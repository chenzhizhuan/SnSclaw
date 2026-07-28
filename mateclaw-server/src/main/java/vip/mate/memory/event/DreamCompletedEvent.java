package vip.mate.memory.event;

import vip.mate.memory.service.DreamReport;

/**
 * Published when a dream consolidation completes successfully.
 *
 * @param report the structured dream report
 * @author SnSclaw Team
 */
public record DreamCompletedEvent(DreamReport report) {}
