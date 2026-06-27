package personal.knowledge.base.document.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for ingesting raw text. Title is optional and defaults when blank. */
public record TextIngestRequest(String title, @NotBlank String text) {}
