package personal.knowledge.base.ingest;

import java.net.URI;

/** Fetches bounded, textual content from a validated public HTTP(S) URI. */
public interface UrlFetchingService {
    FetchedPage fetch(String submittedUrl);

    record FetchedPage(URI uri, String text) {}
}
