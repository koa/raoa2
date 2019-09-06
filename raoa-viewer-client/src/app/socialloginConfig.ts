import {AuthServiceConfig, GoogleLoginProvider} from 'angularx-social-login';

export function getAuthServiceConfigs() {
  let config = new AuthServiceConfig([
    {
      id: GoogleLoginProvider.PROVIDER_ID,
      provider: new GoogleLoginProvider("3663583296-uhlnbj4vp9h8pm2ldnuap9hu9kbiujfi.apps.googleusercontent.com")
    }]);

  return config;
}
