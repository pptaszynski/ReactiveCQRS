package io.reactivecqrs.api.guid

/**
 * Globally unique id that identifies single aggregate in whole application.
 * @param asLong unique long identifier across aggregates.
 */
case class AggregateId(asLong: Long)
