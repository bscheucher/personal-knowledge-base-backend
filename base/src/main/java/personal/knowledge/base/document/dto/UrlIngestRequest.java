package personal.knowledge.base.document.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for ingesting a document from a URL. */
public record UrlIngestRequest(@NotBlank String url) {}
