package com.menumaestro.backend.knowledge;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.menumaestro.backend.common.web.RequestBodies;
import com.menumaestro.backend.common.web.Responses;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

/** Admin editing of the restaurant knowledge document. */
@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeController {
  private final KnowledgeStore knowledge;

  public KnowledgeController(KnowledgeStore knowledge) { this.knowledge = knowledge; }

  @GetMapping
  public ResponseEntity<String> read() { return Responses.ok(knowledge.read().toJson()); }

  @PatchMapping
  public ResponseEntity<String> save(HttpServletRequest request) {
    JsonNode body = KnowledgeStore.UPDATE.parse(RequestBodies.json(request, 160000));
    return Responses.ok(knowledge.save(body).toJson());
  }
}
