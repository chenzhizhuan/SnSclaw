package vip.mate.memory.event;

import vip.mate.memory.service.DreamReport;

/**
 * Published when a dream consolidation fails.
 *
 * @param report the structured dream report (status=FAILED)
 * @author SnSclaw
 */
public record DreamFailedEvent(DreamReport report) {}
