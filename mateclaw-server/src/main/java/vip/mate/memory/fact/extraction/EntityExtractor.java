package vip.mate.memory.fact.extraction;

import java.util.List;

/**
 * Strategy interface for extracting facts from markdown content.
 *
 * @author SnSclaw
 */
public interface EntityExtractor {

    List<ExtractedFact> extract(Long agentId, String filename, String content);
}
