package com.spectralogic.s3.server.handler.reqhandler.spectrads3.cache;

import com.spectralogic.s3.common.dao.domain.ds3.CacheThrottleRule;
import com.spectralogic.s3.server.handler.auth.DefaultPublicExposureAuthenticationStrategy;
import com.spectralogic.s3.server.handler.command.api.CommandExecutionParams;
import com.spectralogic.s3.server.handler.reqhandler.frmwk.BaseDeleteBeanRequestHandler;
import com.spectralogic.s3.server.request.rest.RestDomainType;
import com.spectralogic.util.net.rpc.frmwrk.RpcFuture;

public class DeleteCacheThrottleRuleRequestHandler extends BaseDeleteBeanRequestHandler<CacheThrottleRule> {
    public DeleteCacheThrottleRuleRequestHandler() {
        super(CacheThrottleRule.class,
                new DefaultPublicExposureAuthenticationStrategy(DefaultPublicExposureAuthenticationStrategy.RequiredAuthentication.ADMINISTRATOR),
                RestDomainType.CACHE_THROTTLE_RULE);
    }

    @Override
    protected void deleteBean(final CommandExecutionParams params, final CacheThrottleRule bean ) {
        super.deleteBean(params, bean);
        try
        {
            params.getPlannerResource().invalidateCachedRule(bean.getId()).get( RpcFuture.Timeout.DEFAULT );
        } catch ( final Exception e )
        {
            LOG.warn( "Failed to invalidate cached throttle rule " + bean.getId() + " on delete.", e );
        }
    }
}
