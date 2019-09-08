import {BrowserModule} from '@angular/platform-browser';
import {APP_INITIALIZER, NgModule} from '@angular/core';

import {AppRoutingModule} from './app-routing.module';
import {AppComponent} from './app.component';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {LoginComponent} from './components/login/login.component';
import {SocialLoginModule} from 'angularx-social-login';
import {AppConfigService} from './services/app-config.service';
import {HTTP_INTERCEPTORS, HttpClientModule} from '@angular/common/http';
import {MatButtonModule} from '@angular/material/button';
import {AlbumListComponent} from './components/album-list/album-list.component';
import {ApolloModule} from 'apollo-angular';
import {HttpLinkModule} from 'apollo-angular-link-http';
import {MatIconModule} from '@angular/material/icon';
import {MatSidenavModule} from '@angular/material/sidenav';
import {MatListModule} from '@angular/material/list';
import {MatToolbarModule} from '@angular/material/toolbar';
import {AlbumContentComponent, ShowImageDialogComponent} from './components/album-content/album-content.component';
import {ScrollDispatchModule} from '@angular/cdk/scrolling';
import {ScrollingModule as ExperimentalScrollingModule} from '@angular/cdk-experimental/scrolling';
import {MatDialogModule} from '@angular/material/dialog';
import {AngularResizedEventModule} from 'angular-resize-event';
import {MatProgressBarModule} from '@angular/material/progress-bar';
import {BearerHttpInterceptor} from './interceptor/bearer-http-interceptor';


const appInitializerFn = (appConfig: AppConfigService) => {
  console.log('app initializer fn');
  return () => appConfig.loadAppConfig();
};

@NgModule({
  declarations: [
    AppComponent,
    LoginComponent,
    AlbumListComponent,
    AlbumContentComponent,
    ShowImageDialogComponent,
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    BrowserAnimationsModule,
    SocialLoginModule,
    HttpClientModule,
    MatButtonModule,
    MatIconModule,
    MatSidenavModule,
    MatListModule,
    MatToolbarModule,
    ScrollDispatchModule,
    ExperimentalScrollingModule,
    MatDialogModule,
    AngularResizedEventModule,
    MatProgressBarModule,
  ],
  providers: [
    AppConfigService,
    {
      provide: APP_INITIALIZER,
      useFactory: appInitializerFn,
      multi: true,
      deps: [AppConfigService]
    }, {
      // register the interceptor to our angular module
      provide: HTTP_INTERCEPTORS, useClass: BearerHttpInterceptor, multi: true
    }
  ],
  bootstrap: [AppComponent],
  exports: [ApolloModule, HttpLinkModule],
  entryComponents: [ShowImageDialogComponent]
})
export class AppModule {
}
