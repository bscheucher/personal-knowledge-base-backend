package personal.knowledge.base.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** SSRF-resistant fetcher. DNS answers are validated by the resolver used for the connection. */
@Service
@EnableConfigurationProperties(UrlFetchProperties.class)
public class SecureUrlFetchingService implements UrlFetchingService {
    static final String SAFE_ERROR = "The URL could not be fetched safely";
    private static final Set<String> TEXT_APPLICATION_TYPES =
            Set.of("application/json", "application/xml", "application/xhtml+xml", "application/rss+xml", "application/atom+xml");
    private static final Pattern CHARSET = Pattern.compile("(?i)(?:^|;)\\s*charset=\\s*[\"']?([^;\"']+)");

    private final UrlFetchProperties properties;
    private final HostResolver hostResolver;

    public SecureUrlFetchingService(UrlFetchProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    SecureUrlFetchingService(UrlFetchProperties properties, HostResolver hostResolver) {
        this.properties = properties;
        this.hostResolver = hostResolver;
    }

    @Override
    public FetchedPage fetch(String submittedUrl) {
        URI current = parseAndNormalize(submittedUrl);
        for (int redirects = 0; ; redirects++) {
            validateDestination(current);
            FetchResponse response = execute(current);
            if (response.redirectLocation() != null) {
                if (redirects >= properties.getMaxRedirects()) {
                    throw safeFailure(null);
                }
                try {
                    current = parseAndNormalize(current.resolve(response.redirectLocation()).toString());
                } catch (IllegalArgumentException e) {
                    throw safeFailure(e);
                }
                continue;
            }
            if (response.status() < 200 || response.status() >= 300 || response.body() == null) {
                throw safeFailure(null);
            }
            ensureTextual(response.contentType());
            Charset charset = charset(response.contentType());
            String decoded = new String(response.body(), charset);
            String text = isHtml(response.contentType()) ? Jsoup.parse(decoded, current.toString()).text() : decoded;
            if (text.isBlank()) {
                throw safeFailure(null);
            }
            return new FetchedPage(current, text);
        }
    }

    URI parseAndNormalize(String value) {
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getRawUserInfo() != null
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw safeFailure(null);
            }
            String uriHost = uri.getHost();
            String host = uriHost.indexOf(':') >= 0
                    ? uriHost
                    : IDN.toASCII(uriHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isBlank() || host.equals("localhost") || host.endsWith(".localhost")) throw safeFailure(null);
            int port = uri.getPort();
            if (port < -1 || port > 65535) throw safeFailure(null);
            return new URI(scheme, null, host, port, uri.getRawPath().isEmpty() ? "/" : uri.getRawPath(),
                    uri.getRawQuery(), null);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw safeFailure(e);
        }
    }

    void validateDestination(URI uri) {
        try {
            InetAddress[] addresses = hostResolver.resolve(uri.getHost());
            if (addresses == null || addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !isPublic(a))) {
                throw safeFailure(null);
            }
        } catch (UnknownHostException e) {
            throw safeFailure(e);
        }
    }

    private FetchResponse execute(URI uri) {
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] addresses = hostResolver.resolve(host);
                if (addresses == null || addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !isPublic(a)))
                    throw new UnknownHostException("Rejected address");
                return addresses;
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                resolve(host);
                return host;
            }
        };
        ConnectionConfig connectionConfig =
                ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.getConnectTimeout()))
                        .build();
        var manager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();
        RequestConfig config = RequestConfig.custom()
                .setResponseTimeout(Timeout.of(properties.getReadTimeout()))
                .setRedirectsEnabled(false).build();
        try (CloseableHttpClient client = HttpClients.custom().setConnectionManager(manager)
                .setDefaultRequestConfig(config).disableRedirectHandling().build()) {
            HttpGet request = new HttpGet(uri);
            request.setHeader("User-Agent", properties.getUserAgent());
            request.setHeader("Accept", "text/html, text/plain, application/xhtml+xml, application/json, application/xml;q=0.9");
            return client.execute(request, this::readResponse);
        } catch (IOException e) {
            throw safeFailure(e);
        }
    }

    private FetchResponse readResponse(ClassicHttpResponse response) throws IOException {
        int status = response.getCode();
        String location = status >= 300 && status < 400 && response.getFirstHeader("Location") != null
                ? response.getFirstHeader("Location").getValue() : null;
        if (location != null) return new FetchResponse(status, null, null, location);
        HttpEntity entity = response.getEntity();
        if (entity == null) return new FetchResponse(status, null, null, null);
        long max = properties.getMaxResponseSize().toBytes();
        if (entity.getContentLength() > max) throw safeFailure(null);
        try (InputStream input = entity.getContent(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int read; (read = input.read(buffer)) != -1; ) {
                total += read;
                if (total > max) throw safeFailure(null);
                output.write(buffer, 0, read);
            }
            String type = response.getFirstHeader("Content-Type") == null ? null : response.getFirstHeader("Content-Type").getValue();
            return new FetchResponse(status, type, output.toByteArray(), null);
        }
    }

    private void ensureTextual(String contentType) {
        if (contentType == null) throw safeFailure(null);
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!mediaType.startsWith("text/") && !TEXT_APPLICATION_TYPES.contains(mediaType)) throw safeFailure(null);
    }

    private boolean isHtml(String contentType) {
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.equals("text/html") || type.equals("application/xhtml+xml");
    }

    private Charset charset(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) return StandardCharsets.UTF_8;
        try { return Charset.forName(matcher.group(1).trim()); }
        catch (Exception ignored) { throw safeFailure(null); }
    }

    static boolean isPublic(InetAddress address) {
        byte[] b = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && c >= 64 && c <= 127) || (a == 169 && c == 254)
                    || (a == 172 && c >= 16 && c <= 31) || (a == 192 && c == 0)
                    || (a == 192 && c == 168) || (a == 192 && c == 88 && d == 99)
                    || (a == 198 && (c == 18 || c == 19)) || (a == 198 && c == 51 && d == 100)
                    || (a == 203 && c == 0 && d == 113));
        }
        // Only globally routable unicast IPv6 (2000::/3); exclude documentation space.
        return (b[0] & 0xe0) == 0x20 && !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && (b[3] & 255) == 0xb8);
    }

    private IngestException safeFailure(Throwable cause) {
        return cause == null ? new IngestException(SAFE_ERROR) : new IngestException(SAFE_ERROR, cause);
    }

    @FunctionalInterface
    interface HostResolver { InetAddress[] resolve(String hostname) throws UnknownHostException; }
    private record FetchResponse(int status, String contentType, byte[] body, String redirectLocation) {}
}
