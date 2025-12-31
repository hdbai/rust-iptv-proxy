package com.iptvproxy;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IptvProxyApplication {
    public static void main(String[] args) {
        ArgsParser.ParseResult parseResult = ArgsParser.parse(args);
        if (parseResult.shouldExit()) {
            return;
        }

        IptvArgs iptvArgs = parseResult.args();
        Map<String, Object> props = new HashMap<>();
        props.put("server.address", iptvArgs.bindAddress());
        props.put("server.port", iptvArgs.bindPort());
        String rustLog = System.getenv("RUST_LOG");
        if (rustLog != null && !rustLog.isBlank()) {
            props.put("logging.level.root", rustLog);
        }

        SpringApplication app = new SpringApplication(IptvProxyApplication.class);
        app.setDefaultProperties(props);
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("iptvArgs", iptvArgs));
        app.run(args);
    }
}
