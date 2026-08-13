package com.vyomin.core_api.service;

import java.math.BigDecimal;

/**
 * Equal-weight average forward return across a ticker basket for one event.
 * averageReturn is null when no ticker in the basket had a computable return (contributingCount
 * == 0) - callers must not treat that as a 0% average.
 */
public record BasketForwardReturn(BigDecimal averageReturn, int contributingCount, int requestedCount) {
}