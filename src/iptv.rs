use crate::args::Args;
use anyhow::{anyhow, Result};
use des::{
    cipher::{block_padding::Pkcs7, BlockEncryptMut, KeyInit},
    TdesEde3,
};
#[cfg(not(any(target_os = "android", target_os = "fuchsia", target_os = "linux")))]
use local_ip_address::list_afinet_netifas;
use log::{debug, info};
use rand::Rng;
use regex_lite::Regex;
use reqwest::Client;
use serde::Deserialize;
use std::{
    collections::HashMap,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::RwLock;

use futures_util::stream::{self, StreamExt};

const HTTP_TIMEOUT: Duration = Duration::from_secs(5);
const BASE_URL_TTL: Duration = Duration::from_secs(6 * 60 * 60);
const CHANNELS_TTL: Duration = Duration::from_secs(10 * 60);
const EPG_TTL: Duration = Duration::from_secs(30 * 60);
const EPG_CONCURRENCY: usize = 12;
const DAY_MS: u128 = 86_400_000;

struct Cached<T> {
    value: T,
    expires_at: Instant,
}

impl<T> Cached<T> {
    fn new(value: T, ttl: Duration) -> Self {
        Self {
            value,
            expires_at: Instant::now() + ttl,
        }
    }

    fn is_valid(&self) -> bool {
        Instant::now() < self.expires_at
    }
}

struct ChannelCache {
    scheme: String,
    host: String,
    channels: Vec<Channel>,
}

impl ChannelCache {
    fn matches(&self, scheme: &str, host: &str) -> bool {
        self.scheme == scheme && self.host == host
    }
}

pub(crate) struct IptvState {
    client: Client,
    base_url: RwLock<Option<Cached<String>>>,
    channels: RwLock<Option<Cached<ChannelCache>>>,
    epg: RwLock<Option<Cached<ChannelCache>>>,
}

impl IptvState {
    pub(crate) fn new(args: &Args) -> Result<Self> {
        let client = build_client(args.interface.as_deref())?;
        Ok(Self {
            client,
            base_url: RwLock::new(None),
            channels: RwLock::new(None),
            epg: RwLock::new(None),
        })
    }

    pub(crate) fn client(&self) -> &Client {
        &self.client
    }
}

fn build_client(#[allow(unused_variables)] if_name: Option<&str>) -> Result<Client> {
    #[allow(unused_mut)]
    let mut client = Client::builder()
        .timeout(HTTP_TIMEOUT)
        .cookie_store(true);

    #[cfg(not(any(target_os = "android", target_os = "fuchsia", target_os = "linux")))]
    if let Some(i) = if_name {
        let network_interfaces = list_afinet_netifas()?;
        for (name, ip) in network_interfaces.iter() {
            debug!("{}: {}", name, ip);
            if name == i {
                client = client.local_address(ip.to_owned());
                break;
            }
        }
    }

    #[cfg(any(target_os = "android", target_os = "fuchsia", target_os = "linux"))]
    if let Some(i) = if_name {
        client = client.interface(i);
    }

    Ok(client.build()?)
}

async fn get_base_url(state: &IptvState, args: &Args) -> Result<String> {
    if let Some(cached) = state.base_url.read().await.as_ref() {
        if cached.is_valid() {
            return Ok(cached.value.clone());
        }
    }

    let user = args.user.as_str();

    let params = [("Action", "Login"), ("return_type", "1"), ("UserID", user)];

    let url = reqwest::Url::parse_with_params(
        "http://eds.iptv.gd.cn:8082/EDS/jsp/AuthenticationURL",
        params,
    )?;

    let response = state.client.get(url).send().await?.error_for_status()?;

    let epgurl = reqwest::Url::parse(response.json::<AuthJson>().await?.epgurl.as_str())?;
    let base_url = format!(
        "{}://{}:{}",
        epgurl.scheme(),
        epgurl.host_str().ok_or(anyhow!("no host"))?,
        epgurl.port_or_known_default().ok_or(anyhow!("no host"))?,
    );
    debug!("Got base_url {base_url}");

    *state.base_url.write().await = Some(Cached::new(base_url.clone(), BASE_URL_TTL));

    Ok(base_url)
}

#[derive(Clone)]
pub(crate) struct Program {
    pub(crate) start: i64,
    pub(crate) stop: i64,
    pub(crate) title: String,
    pub(crate) desc: String,
}

#[derive(Clone)]
pub(crate) struct Channel {
    pub(crate) id: u64,
    pub(crate) name: String,
    pub(crate) rtsp: String,
    pub(crate) igmp: Option<String>,
    pub(crate) epg: Vec<Program>,
}

#[derive(Deserialize)]
struct AuthJson {
    epgurl: String,
}

#[derive(Deserialize)]
struct TokenJson {
    #[serde(rename = "EncryToken")]
    encry_token: String,
}

#[derive(Deserialize)]
struct PlaybillList {
    #[serde(rename = "playbillLites")]
    list: Vec<Bill>,
}

#[derive(Deserialize)]
struct Bill {
    name: String,
    #[serde(rename = "startTime")]
    start_time: i64,
    #[serde(rename = "endTime")]
    end_time: i64,
}

pub(crate) async fn get_channels(
    state: &IptvState,
    args: &Args,
    need_epg: bool,
    scheme: &str,
    host: &str,
) -> Result<Vec<Channel>> {
    if need_epg {
        if let Some(cached) = state.epg.read().await.as_ref() {
            if cached.is_valid() && cached.value.matches(scheme, host) {
                return Ok(cached.value.channels.clone());
            }
        }
    } else if let Some(cached) = state.channels.read().await.as_ref() {
        if cached.is_valid() && cached.value.matches(scheme, host) {
            return Ok(cached.value.channels.clone());
        }
    }

    info!("Obtaining channels");

    let base_url = get_base_url(state, args).await?;

    let mut channels = {
        let cached = state
            .channels
            .read()
            .await
            .as_ref()
            .filter(|cache| cache.is_valid() && cache.value.matches(scheme, host))
            .map(|cache| cache.value.channels.clone());

        if let Some(channels) = cached {
            channels
        } else {
            let channels = fetch_channels(state, args, &base_url, scheme, host).await?;
            *state.channels.write().await = Some(Cached::new(
                ChannelCache {
                    scheme: scheme.to_string(),
                    host: host.to_string(),
                    channels: channels.clone(),
                },
                CHANNELS_TTL,
            ));
            channels
        }
    };

    if !need_epg {
        return Ok(channels);
    }

    if let Some(cached) = state.epg.read().await.as_ref() {
        if cached.is_valid() && cached.value.matches(scheme, host) {
            return Ok(cached.value.channels.clone());
        }
    }

    channels = fetch_epg(&state.client, &base_url, channels).await?;
    *state.epg.write().await = Some(Cached::new(
        ChannelCache {
            scheme: scheme.to_string(),
            host: host.to_string(),
            channels: channels.clone(),
        },
        EPG_TTL,
    ));

    Ok(channels)
}

async fn fetch_channels(
    state: &IptvState,
    args: &Args,
    base_url: &str,
    scheme: &str,
    host: &str,
) -> Result<Vec<Channel>> {
    let user = args.user.as_str();
    let passwd = args.passwd.as_str();
    let mac = args.mac.as_str();
    let imei = args.imei.as_str();
    let ip = args.address.as_str();

    let params = [
        ("response_type", "EncryToken"),
        ("client_id", "smcphone"),
        ("userid", user),
    ];
    let url = reqwest::Url::parse_with_params(
        format!("{base_url}/EPG/oauth/v2/authorize").as_str(),
        params,
    )?;
    let response = state.client.get(url).send().await?.error_for_status()?;

    let token = response.json::<TokenJson>().await?.encry_token;

    debug!("Got token {token}");

    let enc = ecb::Encryptor::<TdesEde3>::new_from_slice(
        format!("{:X}", md5::compute(passwd.as_bytes()))[0..24].as_bytes(),
    );
    let enc = match enc {
        Ok(enc) => Ok(enc),
        Err(e) => Err(std::io::Error::new(
            std::io::ErrorKind::Unsupported,
            format!("Encrpy error {e}"),
        )),
    }?;
    let data = format!(
        "{}${token}${user}${imei}${ip}${mac}$$CTC",
        rand::thread_rng().gen_range(0..10000000),
    );
    let auth = hex::encode_upper(enc.encrypt_padded_vec_mut::<Pkcs7>(data.as_bytes()));

    debug!("Got auth {auth}");

    let params = [
        ("client_id", "smcphone"),
        ("DeviceType", "deviceType"),
        ("UserID", user),
        ("DeviceVersion", "deviceVersion"),
        ("userdomain", "2"),
        ("datadomain", "3"),
        ("accountType", "1"),
        ("authinfo", auth.as_str()),
        ("grant_type", "EncryToken"),
    ];
    let url =
        reqwest::Url::parse_with_params(format!("{base_url}/EPG/oauth/v2/token").as_str(), params)?;
    let _response = state.client.get(url).send().await?.error_for_status()?;

    let url = reqwest::Url::parse(format!("{base_url}/EPG/jsp/getchannellistHWCTC.jsp").as_str())?;

    let response = state.client.get(url).send().await?.error_for_status()?;

    let res = response.text().await?;
    let re = Regex::new("Authentication.CTCSetConfig\\('Channel','(.+?)'\\)")?;
    let mut channels = re
        .captures_iter(&res)
        .map(|cap| cap[1].to_string())
        .map(|s| {
            s.split("\",")
                .map(|s| s.split("=\"").collect::<Vec<_>>())
                .filter_map(|s| {
                    s.first()
                        .map(|a| String::from(*a))
                        .and_then(|a| s.get(1).map(|b| String::from(*b)).map(|b| (a, b)))
                })
                .collect::<HashMap<_, _>>()
        })
        .collect::<Vec<_>>();

    let channels = channels
        .iter_mut()
        .filter_map(|m| {
            m.get("ChannelID")
                .and_then(|i| str::parse::<u64>(i).ok())
                .map(|i| (i, m))
        })
        .filter_map(|(i, m)| m.get("ChannelName").cloned().map(|n| (i, n, m)))
        .filter_map(|(i, n, m)| {
            m.get("ChannelURL")
                .and_then(|u| {
                    let rtsp = u.split('|').find(|u| u.starts_with("rtsp"));
                    let igmp = u.split('|').find(|u| u.starts_with("igmp"));
                    rtsp.map(|rtsp| (rtsp, igmp))
                })
                .map(|(rtsp, igmp)| {
                    (
                        if args.rtsp_proxy {
                            rtsp.replace("rtsp://", &format!("{}://{}/rtsp/", scheme, host))
                        } else {
                            rtsp.to_string()
                        }
                        .replace("zoneoffset=0", "zoneoffset=480"),
                        igmp.map(|igmp| {
                            if args.udp_proxy {
                                igmp.replace("igmp://", &format!("{}://{}/udp/", scheme, host))
                            } else {
                                igmp.to_string()
                            }
                        }),
                    )
                })
                .map(|u| (i, n, u))
        })
        .map(|(i, n, (rtsp, igmp))| Channel {
            id: i,
            name: n.to_owned(),
            rtsp,
            igmp,
            epg: vec![],
        })
        .collect::<Vec<_>>();

    info!("Got {} channel(s)", channels.len());

    Ok(channels)
}

async fn fetch_epg(client: &Client, base_url: &str, channels: Vec<Channel>) -> Result<Vec<Channel>> {
    let now = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis();
    let base_url = base_url.to_string();

    let mut tasks = stream::iter(channels.into_iter().map(|mut channel| {
        let client = client.clone();
        let base_url = base_url.clone();
        async move {
            let params = [
                ("channelId", format!("{}", channel.id)),
                ("begin", format!("{}", now - DAY_MS * 2)),
                ("end", format!("{}", now + DAY_MS * 5)),
            ];
            let url = match reqwest::Url::parse_with_params(
                format!("{base_url}/EPG/jsp/iptvsnmv3/en/play/ajax/_ajax_getPlaybillList.jsp")
                    .as_str(),
                params,
            ) {
                Ok(url) => url,
                Err(e) => {
                    debug!("EPG url error: {}", e);
                    return channel;
                }
            };

            match client.get(url).send().await.and_then(|res| res.error_for_status()) {
                Ok(res) => {
                    if let Ok(play_bill_list) = res.json::<PlaybillList>().await {
                        for bill in play_bill_list.list.into_iter() {
                            channel.epg.push(Program {
                                start: bill.start_time,
                                stop: bill.end_time,
                                title: bill.name.clone(),
                                desc: bill.name,
                            })
                        }
                    }
                }
                Err(e) => {
                    debug!("EPG request error: {}", e);
                }
            }

            channel
        }
    }))
    .buffer_unordered(EPG_CONCURRENCY);

    let mut out = Vec::new();
    while let Some(channel) = tasks.next().await {
        out.push(channel);
    }

    Ok(out)
}

pub(crate) async fn get_icon(state: &IptvState, args: &Args, id: &str) -> Result<Vec<u8>> {
    let base_url = get_base_url(state, args).await?;

    let url = reqwest::Url::parse(&format!(
        "{base_url}/EPG/jsp/iptvsnmv3/en/list/images/channelIcon/{}.png",
        id
    ))?;

    let response = state.client.get(url).send().await?.error_for_status()?;
    Ok(response.bytes().await?.to_vec())
}
