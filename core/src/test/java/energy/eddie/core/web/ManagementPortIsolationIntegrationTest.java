// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.core.web;

import energy.eddie.core.ManagementApiConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementPortIsolationIntegrationTest {
    @Test
    void containerDecodedManagementPathsRemainOnTheManagementConnector(@TempDir Path temporary) throws Exception {
        var tomcat = new Tomcat();
        tomcat.setBaseDir(temporary.resolve("tomcat").toString());
        tomcat.setPort(0);
        var publicConnector = tomcat.getConnector();
        publicConnector.setProperty("address", "127.0.0.1");
        var managementConnector = new Connector();
        managementConnector.setPort(0);
        managementConnector.setProperty("address", "127.0.0.1");
        tomcat.getService().addConnector(managementConnector);

        var documentRoot = Files.createDirectories(temporary.resolve("document-root"));
        var context = tomcat.addContext("/eddie", documentRoot.toString());
        Tomcat.addServlet(context, "core", new MappingServlet());
        context.addServletMappingDecoded("/", "core");
        Tomcat.addServlet(context, "regional", new MappingServlet());
        context.addServletMappingDecoded("/region-connectors/at-eda/*", "regional");

        var actualFilter = new AtomicReference<Filter>();
        var definition = new FilterDef();
        definition.setFilterName("management-isolation");
        definition.setFilter((request, response, chain) ->
                Objects.requireNonNull(actualFilter.get()).doFilter(request, response, chain));
        context.addFilterDef(definition);
        var mapping = new FilterMap();
        mapping.setFilterName("management-isolation");
        mapping.addURLPattern("/*");
        context.addFilterMap(mapping);

        try {
            tomcat.start();
            var publicPort = publicConnector.getLocalPort();
            var managementPort = managementConnector.getLocalPort();
            assertTrue(publicPort > 0 && managementPort > 0, "Both loopback connectors must start");
            actualFilter.set(new ManagementApiConfig(managementPort, "management").trustedEndpointsFilter().getFilter());

            String[] managementPaths = {
                    "/management/energy-sharing/at-eda/communities/community/measurements",
                    "/management/energy-sharing/at-eda/communities/community/measurement-documents/doc/source",
                    "/management/energy-sharing/at-eda/operations",
                    "/%6danagement/energy-sharing/at-eda/operations",
                    "/man%61gement/energy-sharing/at-eda/operations",
                    "/management;x=1/energy-sharing/at-eda/operations",
                    "/%6danagement;x=1/energy-sharing/at-eda/operations",
                    "/foo/../management/energy-sharing/at-eda/operations",
                    "/foo/%2e%2e/management/energy-sharing/at-eda/operations",
                    "//management/energy-sharing/at-eda/operations",
                    "/region-connectors/at-eda/%6danagement/operations",
                    "/region-connectors/at-eda;x=1/management/operations",
                    "/%72egion-connectors/at-eda/management/operations",
                    "/data-needs/%6danagement",
                    "/%6futbound-connectors/rest/energy-sharing"
            };
            for (var path : managementPaths) {
                assertEquals(404, exchange(publicPort, path).status(), "Public port must reject " + path);
                assertEquals(200, exchange(managementPort, path).status(), "Management port must handle " + path);
            }
            var regional = exchange(managementPort, "/region-connectors/at-eda/%6danagement/operations");
            assertTrue(regional.raw().contains("servlet=/region-connectors/at-eda;info=/management/operations"));
            assertEquals(200, exchange(publicPort, "/lib/eddie.js").status());
            assertEquals(404, exchange(managementPort, "/lib/eddie.js").status());
            assertEquals(400, exchange(publicPort, "/management%2fenergy-sharing/at-eda/operations").status());
            assertEquals(400, exchange(managementPort, "/management%2fenergy-sharing/at-eda/operations").status());
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static Reply exchange(int port, String path) throws IOException {
        // A raw request preserves encoded characters and dot segments rather than normalising them in the client.
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            var request = "GET /eddie" + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            var raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Reply(Integer.parseInt(raw.split(" ", 3)[1]), raw);
        }
    }

    private record Reply(int status, String raw) {}

    private static class MappingServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("text/plain");
            response.getWriter().print("servlet=" + request.getServletPath() + ";info=" + request.getPathInfo());
        }
    }
}
