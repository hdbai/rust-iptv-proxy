package com.iptvproxy.service;

import com.iptvproxy.model.Channel;
import com.iptvproxy.model.Program;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import org.springframework.stereotype.Component;

@Component
public class XmltvFormatter {
    private static final DateTimeFormatter XMLTV_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.ofHours(8));

    public String buildXmltv(List<Channel> channels, String extraXml) {
        try {
            StringWriter buffer = new StringWriter();
            XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
            XMLEventWriter writer = outputFactory.createXMLEventWriter(buffer);
            var eventFactory = javax.xml.stream.XMLEventFactory.newFactory();
            writer.add(eventFactory.createStartDocument("UTF-8", "1.0"));
            writer.add(eventFactory.createStartElement("", "", "tv"));
            writer.add(eventFactory.createAttribute("generator-info-name", "iptv-proxy"));
            writer.add(eventFactory.createAttribute("source-info-name", "iptv-proxy"));

            for (Channel channel : channels) {
                writer.add(eventFactory.createStartElement("", "", "channel"));
                writer.add(eventFactory.createAttribute("id", Long.toString(channel.id())));
                writer.add(eventFactory.createStartElement("", "", "display-name"));
                writer.add(eventFactory.createCharacters(channel.name()));
                writer.add(eventFactory.createEndElement("", "", "display-name"));
                writer.add(eventFactory.createEndElement("", "", "channel"));
            }

            if (extraXml != null && !extraXml.isBlank()) {
                appendExtraXml(writer, extraXml);
            }

            for (Channel channel : channels) {
                for (Program epg : channel.epg()) {
                    writer.add(eventFactory.createStartElement("", "", "programme"));
                    writer.add(eventFactory.createAttribute("start",
                        XMLTV_FORMATTER.format(Instant.ofEpochMilli(epg.start())) + " +0800"));
                    writer.add(eventFactory.createAttribute("stop",
                        XMLTV_FORMATTER.format(Instant.ofEpochMilli(epg.stop())) + " +0800"));
                    writer.add(eventFactory.createAttribute("channel", Long.toString(channel.id())));
                    writer.add(eventFactory.createStartElement("", "", "title"));
                    writer.add(eventFactory.createAttribute("lang", "chi"));
                    writer.add(eventFactory.createCharacters(epg.title()));
                    writer.add(eventFactory.createEndElement("", "", "title"));
                    if (epg.desc() != null && !epg.desc().isBlank()) {
                        writer.add(eventFactory.createStartElement("", "", "desc"));
                        writer.add(eventFactory.createCharacters(epg.desc()));
                        writer.add(eventFactory.createEndElement("", "", "desc"));
                    }
                    writer.add(eventFactory.createEndElement("", "", "programme"));
                }
            }
            writer.add(eventFactory.createEndElement("", "", "tv"));
            writer.add(eventFactory.createEndDocument());
            writer.flush();
            writer.close();
            return buffer.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to build XMLTV", e);
        }
    }

    private void appendExtraXml(XMLEventWriter writer, String extraXml) throws XMLStreamException {
        XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        XMLEventReader reader = inputFactory.createXMLEventReader(new StringReader(extraXml));
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement startElement = event.asStartElement();
                String name = startElement.getName().getLocalPart();
                if (!isAllowed(name)) {
                    continue;
                }
                if ("title".equals(name) && !isChineseTitle(startElement)) {
                    continue;
                }
                writer.add(startElement);
            } else if (event.isCharacters()) {
                writer.add(event);
            } else if (event.isEndElement()) {
                EndElement endElement = event.asEndElement();
                String name = endElement.getName().getLocalPart();
                if (!isAllowed(name)) {
                    continue;
                }
                writer.add(endElement);
            }
        }
    }

    private boolean isAllowed(String name) {
        return switch (name) {
            case "channel", "display-name", "desc", "title", "sub-title", "programme" -> true;
            default -> false;
        };
    }

    private boolean isChineseTitle(StartElement startElement) {
        for (var attr = startElement.getAttributes(); attr.hasNext(); ) {
            Attribute attribute = (Attribute) attr.next();
            if ("lang".equals(attribute.getName().getLocalPart())) {
                return "chi".equals(attribute.getValue());
            }
        }
        return true;
    }
}
