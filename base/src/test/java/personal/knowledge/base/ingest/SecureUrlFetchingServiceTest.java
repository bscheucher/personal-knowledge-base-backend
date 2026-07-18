package personal.knowledge.base.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecureUrlFetchingServiceTest {
    private final UrlFetchProperties properties = new UrlFetchProperties();

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com", "https://EXAMPLE.com/a/../page?q=1"})
    void acceptsAndNormalizesPublicHttpUris(String value) throws Exception {
        var service = serviceWith("93.184.216.34");
        URI uri = service.parseAndNormalize(value);
        service.validateDestination(uri);
        assertThat(uri.getScheme()).isIn("http", "https");
        assertThat(uri.getHost()).isLowerCase();
        assertThat(uri.getFragment()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://example.com/file", "file:///etc/passwd", "http://user:pass@example.com",
        "http://", "not a url", "http://%31%32%37.0.0.1"
    })
    void rejectsMalformedUnsupportedOrObfuscatedUris(String value) {
        var service = serviceWith("93.184.216.34");
        assertThatThrownBy(() -> service.parseAndNormalize(value))
                .isInstanceOf(IngestException.class)
                .hasMessage(SecureUrlFetchingService.SAFE_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1", "http://[::1]", "http://169.254.169.254/latest/meta-data", "http://2130706433", "http://0x7f000001"})
    void rejectsLiteralPrivateDestinations(String value) {
        var service = new SecureUrlFetchingService(properties);
        URI uri = service.parseAndNormalize(value);
        assertThatThrownBy(() -> service.validateDestination(uri))
                .isInstanceOf(IngestException.class)
                .hasMessage(SecureUrlFetchingService.SAFE_ERROR);
    }

    static Stream<Arguments> nonPublicAddresses() {
        return Stream.of(
                "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254",
                "172.16.0.1", "192.168.1.1", "192.0.2.1", "198.18.0.1", "198.51.100.1",
                "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "fe80::1",
                "fc00::1", "ff02::1", "2001:db8::1")
                .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("nonPublicAddresses")
    void rejectsNonPublicAddressRanges(String address) throws Exception {
        assertThat(SecureUrlFetchingService.isPublic(InetAddress.getByName(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"})
    void acceptsGloballyRoutableAddresses(String address) throws Exception {
        assertThat(SecureUrlFetchingService.isPublic(InetAddress.getByName(address))).isTrue();
    }

    @Test
    void rejectsDnsAnswerContainingBothPublicAndPrivateAddresses() throws Exception {
        var service = new SecureUrlFetchingService(properties, host -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.7")});
        URI uri = service.parseAndNormalize("https://example.com");
        assertThatThrownBy(() -> service.validateDestination(uri))
                .isInstanceOf(IngestException.class)
                .hasMessage(SecureUrlFetchingService.SAFE_ERROR);
    }

    @Test
    void doesNotExposeDnsDetailsInErrors() {
        var service = new SecureUrlFetchingService(properties, host -> {
            throw new UnknownHostException("internal.service.local at 10.0.0.4");
        });
        assertThatThrownBy(() -> service.validateDestination(URI.create("https://example.com/")))
                .hasMessage(SecureUrlFetchingService.SAFE_ERROR)
                .message().doesNotContain("internal", "10.0.0.4");
    }

    private SecureUrlFetchingService serviceWith(String address) {
        return new SecureUrlFetchingService(properties, host ->
                new InetAddress[] {InetAddress.getByName(address)});
    }
}
