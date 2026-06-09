package com.spectralogic.s3.server.handler.reqhandler.spectrads3.cache;

import com.spectralogic.s3.common.dao.domain.ds3.CacheThrottleRule;
import com.spectralogic.s3.common.dao.domain.ds3.Job;
import com.spectralogic.s3.server.handler.auth.DefaultPublicExposureAuthenticationStrategy;
import com.spectralogic.s3.server.handler.command.api.CommandExecutionParams;
import com.spectralogic.s3.server.handler.reqhandler.frmwk.BaseModifyBeanRequestHandler;
import com.spectralogic.s3.server.request.rest.RestDomainType;
import com.spectralogic.util.net.rpc.frmwrk.RpcFuture;

import java.util.Set;

public class ModifyCacheThrottleRuleRequestHandler extends BaseModifyBeanRequestHandler<CacheThrottleRule> {
    public ModifyCacheThrottleRuleRequestHandler() {
        super(CacheThrottleRule.class,
                new DefaultPublicExposureAuthenticationStrategy(DefaultPublicExposureAuthenticationStrategy.RequiredAuthentication.ADMINISTRATOR),
                RestDomainType.CACHE_THROTTLE_RULE);

        registerOptionalBeanProperties(
                CacheThrottleRule.BUCKET_ID,
                CacheThrottleRule.REQUEST_TYPE,
                CacheThrottleRule.PRIORITY,
                CacheThrottleRule.MAX_CACHE_PERCENT,
                CacheThrottleRule.BURST_THRESHOLD);
    }

    @Override
    protected void validateBeanToCommit(final CommandExecutionParams params, final CacheThrottleRule rule, @SuppressWarnings( "unused" ) final Set< String > modifiedProperties ) {
        CreateCacheThrottleRuleRequestHandler.validate(params, rule);
    }

    @Override
    protected void modifyBean(
            final CommandExecutionParams params,
            final CacheThrottleRule bean,
            final Set< String > modifiedProperties ) {
        super.modifyBean( params, bean, modifiedProperties );
        try
        {
            params.getPlannerResource().invalidateCachedRule(bean.getId()).get( RpcFuture.Timeout.DEFAULT );
        } catch ( final Exception e )
        {
            LOG.warn( "Failed to invalidate cached throttle rule " + bean.getId() + " on modify.", e );
        }
    }
}
