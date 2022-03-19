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
package org.thingsboard.server.service.security.auth.mfa.config.account;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.thingsboard.server.service.security.auth.mfa.provider.TwoFactorAuthProviderType;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@EqualsAndHashCode(callSuper = true)
@Data
public class SmsTwoFactorAuthAccountConfig extends OtpBasedTwoFactorAuthAccountConfig {

    @NotBlank
    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Phone number is not of E.164 format")
    private String phoneNumber;

    @Override
    public TwoFactorAuthProviderType getProviderType() {
        return TwoFactorAuthProviderType.SMS;
    }

}
