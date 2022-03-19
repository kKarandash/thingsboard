/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.security.auth.mfa;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.tools.TbRateLimits;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.service.security.auth.mfa.config.TwoFactorAuthConfigManager;
import org.thingsboard.server.service.security.auth.mfa.config.TwoFactorAuthSettings;
import org.thingsboard.server.service.security.auth.mfa.config.account.TwoFactorAuthAccountConfig;
import org.thingsboard.server.service.security.auth.mfa.config.provider.TwoFactorAuthProviderConfig;
import org.thingsboard.server.service.security.auth.mfa.provider.TwoFactorAuthProvider;
import org.thingsboard.server.service.security.auth.mfa.provider.TwoFactorAuthProviderType;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.system.SystemSecurityService;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class DefaultTwoFactorAuthService implements TwoFactorAuthService {

    private final TwoFactorAuthConfigManager configManager;
    private final SystemSecurityService systemSecurityService;
    private final UserService userService;
    private final Map<TwoFactorAuthProviderType, TwoFactorAuthProvider<TwoFactorAuthProviderConfig, TwoFactorAuthAccountConfig>> providers = new EnumMap<>(TwoFactorAuthProviderType.class);

    // FIXME [viacheslav]: remove from the map
    // TODO [viacheslav]: these rate limits are local, and will work bad in the cluster
    private final ConcurrentMap<String, TbRateLimits> verificationCodeSendingRateLimits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TbRateLimits> verificationCodeCheckingRateLimits = new ConcurrentHashMap<>();

    private static final ThingsboardException ACCOUNT_NOT_CONFIGURED = new ThingsboardException("2FA is not configured for account", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
    private static final ThingsboardException PROVIDER_NOT_CONFIGURED = new ThingsboardException("2FA provider is not configured", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
    private static final ThingsboardException PROVIDER_NOT_AVAILABLE = new ThingsboardException("2FA provider is not available", ThingsboardErrorCode.GENERAL);


    @Override
    public void prepareVerificationCode(SecurityUser securityUser, boolean rateLimit) throws Exception {
        TwoFactorAuthAccountConfig accountConfig = configManager.getTwoFaAccountConfig(securityUser.getTenantId(), securityUser.getId())
                .orElseThrow(() -> ACCOUNT_NOT_CONFIGURED);
        prepareVerificationCode(securityUser, accountConfig, rateLimit);
    }

    @Override
    public void prepareVerificationCode(SecurityUser securityUser, TwoFactorAuthAccountConfig accountConfig, boolean rateLimit) throws ThingsboardException {
        TwoFactorAuthSettings twoFaSettings = configManager.getTwoFaSettings(securityUser.getTenantId())
                .orElseThrow(() -> PROVIDER_NOT_CONFIGURED);
        if (rateLimit) {
            if (StringUtils.isNotEmpty(twoFaSettings.getVerificationCodeSendRateLimit())) {
                TbRateLimits rateLimits = verificationCodeSendingRateLimits.computeIfAbsent(securityUser.getSessionId(), sessionId -> {
                    return new TbRateLimits(twoFaSettings.getVerificationCodeSendRateLimit());
                });
                if (!rateLimits.tryConsume()) {
                    throw new ThingsboardException("Too many verification code sending requests", ThingsboardErrorCode.TOO_MANY_REQUESTS);
                }
            }
        }

        TwoFactorAuthProviderConfig providerConfig = twoFaSettings.getProviderConfig(accountConfig.getProviderType())
                .orElseThrow(() -> PROVIDER_NOT_CONFIGURED);
        getTwoFaProvider(accountConfig.getProviderType()).prepareVerificationCode(securityUser, providerConfig, accountConfig);
    }

    @Override
    public boolean checkVerificationCode(SecurityUser securityUser, String verificationCode, boolean rateLimit) throws ThingsboardException {
        TwoFactorAuthAccountConfig accountConfig = configManager.getTwoFaAccountConfig(securityUser.getTenantId(), securityUser.getId())
                .orElseThrow(() -> ACCOUNT_NOT_CONFIGURED);
        return checkVerificationCode(securityUser, verificationCode, accountConfig, rateLimit);
    }

    @Override
    public boolean checkVerificationCode(SecurityUser securityUser, String verificationCode, TwoFactorAuthAccountConfig accountConfig, boolean rateLimit) throws ThingsboardException {
        if (!userService.findUserCredentialsByUserId(securityUser.getTenantId(), securityUser.getId()).isEnabled()) {
            throw new ThingsboardException("User is disabled", ThingsboardErrorCode.AUTHENTICATION);
        }

        TwoFactorAuthSettings twoFaSettings = configManager.getTwoFaSettings(securityUser.getTenantId())
                .orElseThrow(() -> PROVIDER_NOT_CONFIGURED);
        if (rateLimit) {
            if (StringUtils.isNotEmpty(twoFaSettings.getVerificationCodeCheckRateLimit())) {
                TbRateLimits rateLimits = verificationCodeCheckingRateLimits.computeIfAbsent(securityUser.getSessionId(), sessionId -> {
                    return new TbRateLimits(twoFaSettings.getVerificationCodeCheckRateLimit());
                });
                if (!rateLimits.tryConsume()) {
                    throw new ThingsboardException("Too many verification code checking requests", ThingsboardErrorCode.TOO_MANY_REQUESTS);
                }
            }
        }

        TwoFactorAuthProviderConfig providerConfig = twoFaSettings.getProviderConfig(accountConfig.getProviderType())
                .orElseThrow(() -> PROVIDER_NOT_CONFIGURED);
        boolean verificationSuccess = getTwoFaProvider(accountConfig.getProviderType()).checkVerificationCode(securityUser, verificationCode, providerConfig, accountConfig);
        if (rateLimit) {
            systemSecurityService.validateTwoFaVerification(securityUser.getTenantId(), securityUser.getId(), verificationSuccess, twoFaSettings);
        }
        return verificationSuccess;
    }

    @Override
    public TwoFactorAuthAccountConfig generateNewAccountConfig(User user, TwoFactorAuthProviderType providerType) throws ThingsboardException {
        TwoFactorAuthProviderConfig providerConfig = getTwoFaProviderConfig(user.getTenantId(), providerType);
        return getTwoFaProvider(providerType).generateNewAccountConfig(user, providerConfig);
    }


    private TwoFactorAuthProviderConfig getTwoFaProviderConfig(TenantId tenantId, TwoFactorAuthProviderType providerType) throws ThingsboardException {
        return configManager.getTwoFaSettings(tenantId)
                .flatMap(twoFaSettings -> twoFaSettings.getProviderConfig(providerType))
                .orElseThrow(() -> PROVIDER_NOT_CONFIGURED);
    }

    private TwoFactorAuthProvider<TwoFactorAuthProviderConfig, TwoFactorAuthAccountConfig> getTwoFaProvider(TwoFactorAuthProviderType providerType) throws ThingsboardException {
        return Optional.ofNullable(providers.get(providerType))
                .orElseThrow(() -> PROVIDER_NOT_AVAILABLE);
    }

    @Autowired
    private void setProviders(Collection<TwoFactorAuthProvider<TwoFactorAuthProviderConfig, TwoFactorAuthAccountConfig>> providers) {
        providers.forEach(provider -> {
            this.providers.put(provider.getType(), provider);
        });
    }

}

