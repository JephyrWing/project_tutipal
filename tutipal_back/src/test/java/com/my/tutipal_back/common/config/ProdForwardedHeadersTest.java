package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * PT-110: the prod profile on a real embedded Tomcat (application.yaml + application-prod.yaml,
 * no database). Requests come from 127.0.0.1 like cloudflared on the Docker network.
 * X-Forwarded-Proto must make the request https; X-Forwarded-Host/Port must be ignored so the
 * public Host header decides the OAuth redirect_uri host.
 */
class ProdForwardedHeadersTest {

    private static final String PUBLIC_HOST = "tutipal.faultfinder.link";

    private static ConfigurableApplicationContext context;
    private static int port;

    @SpringBootConfiguration
    @ImportAutoConfiguration(TomcatServletWebServerAutoConfiguration.class)
    static class ProbeApp {

        @Bean
        ServletRegistrationBean<HttpServlet> probe() {
            HttpServlet servlet = new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    resp.setContentType("text/plain");
                    resp.getWriter().write("scheme=" + req.getScheme()
                            + " secure=" + req.isSecure()
                            + " host=" + req.getServerName()
                            + " port=" + req.getServerPort()
                            + " url=" + req.getRequestURL());
                }
            };
            return new ServletRegistrationBean<>(servlet, "/probe");
        }
    }

    @BeforeAll
    static void startTomcat() {
        context = new SpringApplicationBuilder(ProbeApp.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .properties("server.port=0", "server.address=127.0.0.1")
                .run();
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stopTomcat() {
        if (context != null) {
            context.close();
        }
    }

    private static String get(String... extraHeaders) throws IOException {
        StringBuilder request = new StringBuilder("GET /probe HTTP/1.1\r\nHost: " + PUBLIC_HOST + "\r\n");
        for (String header : extraHeaders) {
            request.append(header).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void forwardedProtoMakesRequestHttpsOnPublicHost() throws IOException {
        String response = get("X-Forwarded-Proto: https");

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(response).contains("scheme=https secure=true host=" + PUBLIC_HOST + " port=443 url=https://"
                + PUBLIC_HOST + "/probe");
    }

    @Test
    void forwardedHostAndPortFromClientAreIgnored() throws IOException {
        String response = get("X-Forwarded-Proto: https", "X-Forwarded-Host: evil.example", "X-Forwarded-Port: 8443");

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(response).doesNotContain("evil.example").doesNotContain("8443");
        assertThat(response).contains("host=" + PUBLIC_HOST + " port=443 url=https://" + PUBLIC_HOST + "/probe");
    }

    @Test
    void withoutForwardedProtoRequestStaysHttp() throws IOException {
        String response = get();

        assertThat(response).contains("scheme=http secure=false host=" + PUBLIC_HOST);
    }
}
