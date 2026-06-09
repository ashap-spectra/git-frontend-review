package com.spectralogic.s3.dataplanner.cache;

import com.spectralogic.s3.common.dao.domain.ds3.*;
import com.spectralogic.s3.common.dao.domain.planner.CacheEntryState;
import com.spectralogic.s3.dataplanner.frmwk.DataPlannerException;
import com.spectralogic.util.db.query.Require;
import com.spectralogic.util.db.query.WhereClause;
import com.spectralogic.util.db.service.api.BeansServiceManager;
import com.spectralogic.util.exception.GenericFailure;
import com.spectralogic.util.lang.NamingConventionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class CacheThrottleManager {

    CacheThrottleManager(final BeansServiceManager serviceManager) {
        m_serviceManager = serviceManager;
    }

    public void throttleCacheAllocation(final JobRequestType requestType, final BlobStoreTaskPriority priority, final UUID bucketId, final long numBytesToAllocate, final long usedCapacityInBytes, final long totalCapacityInBytes) {
        final Set<CacheThrottleRule> rules = getApplicableCacheThrottleRules(requestType, priority, bucketId);
        if(rules.isEmpty()) {
            return;
        }
        for (final CacheThrottleRule rule : rules) {
            if (rule.getBurstThreshold() != null && usedCapacityInBytes > rule.getBurstThreshold() * totalCapacityInBytes) {
                continue;
            }
            if (exceedsThreshold(rule, numBytesToAllocate, totalCapacityInBytes)) {
                final long ruleMaxCapacityBytes = (long) (rule.getMaxCachePercent() * totalCapacityInBytes);
                final CacheThrottleRuleStats stats = m_cacheThrottleRuleUsage.get(rule.getId());
                throw new DataPlannerException(
                        GenericFailure.RETRY_WITH_SYNCHRONOUS_WAIT,
                        "Will not allocate capacity at this time due to throttling restriction: need "
                                + numBytesToAllocate + " but " + stats.getCachedUsedCapacityInBytes() + "/" + ruleMaxCapacityBytes
                                + " bytes already allocated for rule "
                                + rule.toJson(NamingConventionType.CAMEL_CASE_WITH_FIRST_LETTER_LOWERCASE) + ".");
            }
        }

        updateUsedCapacities(rules, numBytesToAllocate);
    }

    protected Set<CacheThrottleRule> getApplicableCacheThrottleRules(final JobRequestType requestType, final BlobStoreTaskPriority priority, final UUID bucketId) {
        final WhereClause filter = Require.all(
                Require.any(
                        Require.beanPropertyEquals(CacheThrottleRule.REQUEST_TYPE, requestType),
                        Require.beanPropertyNull(CacheThrottleRule.REQUEST_TYPE)),
                Require.any(
                        Require.beanPropertyEquals(CacheThrottleRule.BUCKET_ID, bucketId),
                        Require.beanPropertyNull(CacheThrottleRule.BUCKET_ID)),
                Require.any(
                        Require.beanPropertyEqualsOneOf(CacheThrottleRule.PRIORITY, BlobStoreTaskPriority.prioritiesOfAtLeast(priority)),
                        Require.beanPropertyNull(CacheThrottleRule.PRIORITY)));
        return m_serviceManager.getRetriever(CacheThrottleRule.class).retrieveAll(filter).toSet();
    }

    protected boolean exceedsThreshold(final CacheThrottleRule rule, final long numBytesToAllocate, final long totalCapacityInBytes) {
        final long ruleMaxCapacityBytes = (long) (rule.getMaxCachePercent() * totalCapacityInBytes);

        CacheThrottleRuleStats ruleStats = m_cacheThrottleRuleUsage.get(rule.getId());
        if (ruleStats == null) {
            ruleStats = getActualStats(rule);
            m_cacheThrottleRuleUsage.put(rule.getId(), ruleStats);
        } else {
            if (ruleStats.getCachedUsedCapacityInBytes() + numBytesToAllocate > ruleMaxCapacityBytes && ruleStats.getLastJobEntryNTupDel() != getNumJobEntryRowDeletes()) {
                ruleStats = getActualStats(rule);
                m_cacheThrottleRuleUsage.put(rule.getId(), ruleStats);
            }
        }

        return ruleStats.getCachedUsedCapacityInBytes() + numBytesToAllocate > ruleMaxCapacityBytes;
    }

    protected void updateUsedCapacities(final Set<CacheThrottleRule> rules, final long numBytesAllocated) {
        for (final CacheThrottleRule rule : rules) {
            CacheThrottleRuleStats stats = m_cacheThrottleRuleUsage.get(rule.getId());
            if (stats == null) {
                stats = getActualStats(rule);
                stats.addCapacity(numBytesAllocated);
                m_cacheThrottleRuleUsage.put(rule.getId(), stats);
            } else {
                stats.addCapacity(numBytesAllocated);
            }
        }
    }

    private CacheThrottleRuleStats getActualStats(final CacheThrottleRule rule) {
        final long curRowDeletes = getNumJobEntryRowDeletes();
        final long actualUsedCapacity = getCacheThrottleRuleUsedCapacityInBytes(rule);
        return new CacheThrottleRuleStats(actualUsedCapacity, curRowDeletes);
    }

    protected long getCacheThrottleRuleUsedCapacityInBytes(final CacheThrottleRule rule) {
        final List<WhereClause> filter = new ArrayList<>();
        filter.add(Require.beanPropertyEqualsOneOf(DetailedJobEntry.CACHE_STATE, CacheEntryState.IN_CACHE, CacheEntryState.ALLOCATED));
        if (rule.getBucketId() != null) {
            filter.add(Require.beanPropertyEquals(DetailedJobEntry.BUCKET_ID, rule.getBucketId()));
        }
        if (rule.getRequestType() != null) {
            filter.add(Require.beanPropertyEquals(DetailedJobEntry.REQUEST_TYPE, rule.getRequestType()));
        }
        if (rule.getPriority() != null) {
            filter.add(Require.beanPropertyEqualsOneOf(CacheThrottleRule.PRIORITY, BlobStoreTaskPriority.prioritiesLessThanAndEqualTo(rule.getPriority())));
        }
        return m_serviceManager.getRetriever(DetailedJobEntry.class).getSum(DetailedJobEntry.CACHE_SIZE_IN_BYTES, Require.all(filter));
    }

    public void invalidateCachedRulesWithPriority() {
        final WhereClause filter = Require.beanPropertyNotNull(CacheThrottleRule.PRIORITY);
        final Set<CacheThrottleRule> rulesWithPriority = m_serviceManager.getRetriever(CacheThrottleRule.class).retrieveAll(filter).toSet();
        for (final CacheThrottleRule rule : rulesWithPriority) {
            invalidateCachedRule(rule.getId());
        }
    }

    public void invalidateCachedRule(final UUID ruleId) {
        m_cacheThrottleRuleUsage.remove(ruleId);
    }

    protected long getNumJobEntryRowDeletes() {
        final PgStatAllTables stats = m_serviceManager.getRetriever(PgStatAllTables.class).attain(Require.nothing());
        return stats.getJobEntryNTupDel();
    }

    protected static final class CacheThrottleRuleStats {

        public CacheThrottleRuleStats(final long cachedUsedCapacityInBytes, final long lastJobEntryNTupDel) {
            m_cachedUsedCapacityInBytes = new AtomicLong(cachedUsedCapacityInBytes);
            m_lastJobEntryNTupDel = lastJobEntryNTupDel;
        }

        public long getCachedUsedCapacityInBytes() {
            return m_cachedUsedCapacityInBytes.get();
        }

        public void setCachedUsedCapacityInBytes(long cachedUsedCapacityInBytes) {
            this.m_cachedUsedCapacityInBytes.set(cachedUsedCapacityInBytes);
        }

        public void addCapacity(final long numBytes) {
            this.m_cachedUsedCapacityInBytes.addAndGet(numBytes);
        }

        public long getLastJobEntryNTupDel() {
            return m_lastJobEntryNTupDel;
        }

        public void setLastJobEntryNTupDel(long lastJobEntryNTupDel) {
            this.m_lastJobEntryNTupDel = lastJobEntryNTupDel;
        }

        private final AtomicLong m_cachedUsedCapacityInBytes;
        private long m_lastJobEntryNTupDel;
    }

    private final BeansServiceManager m_serviceManager;
    private final ConcurrentHashMap<UUID, CacheThrottleRuleStats > m_cacheThrottleRuleUsage = new ConcurrentHashMap<>();
}
