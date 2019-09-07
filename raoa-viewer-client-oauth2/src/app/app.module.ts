import {BrowserModule} from '@angular/platform-browser';
import {APP_INITIALIZER, NgModule} from '@angular/core';

import {AppRoutingModule} from './app-routing.module';
import {AppComponent} from './app.component';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {LoginComponent} from './components/login/login.component';
import {SocialLoginModule} from 'angularx-social-login';
import {AppConfigService} from './services/app-config.service';
import {HttpClientModule} from '@angular/common/http';
import {MatButtonModule} from '@angular/material/button';
import {AlbumListComponent} from './components/album-list/album-list.component';
import {ApolloModule} from 'apollo-angular';
import {HttpLinkModule} from 'apollo-angular-link-http';


const appInitializerFn = (appConfig: AppConfigService) => {
  console.log('app initializer fn');
  return () => appConfig.loadAppConfig();
};

@NgModule({
  declarations: [
    AppComponent,
    LoginComponent,
    AlbumListComponent
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    BrowserAnimationsModule,
    SocialLoginModule,
    HttpClientModule,
    MatButtonModule,
  ],
  providers: [
    AppConfigService,
    {
      provide: APP_INITIALIZER,
      useFactory: appInitializerFn,
      multi: true,
      deps: [AppConfigService]
    },
  ],
  bootstrap: [AppComponent],
  exports: [ApolloModule, HttpLinkModule]
})
export class AppModule {
}
