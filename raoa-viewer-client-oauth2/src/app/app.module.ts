import {BrowserModule} from '@angular/platform-browser';
import {APP_INITIALIZER, NgModule} from '@angular/core';

import {AppRoutingModule} from './app-routing.module';
import {AppComponent} from './app.component';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {AppConfigService} from './services/app-config.service';
import {HttpClientModule} from '@angular/common/http';
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
import {CookieService} from 'ngx-cookie-service';


const appInitializerFn = (appConfig: AppConfigService) => {
  return () => appConfig.loadAppConfig();
};

@NgModule({
  declarations: [
    AppComponent,
    AlbumListComponent,
    AlbumContentComponent,
    ShowImageDialogComponent,
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    BrowserAnimationsModule,
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
    },
    CookieService
  ],
  bootstrap: [AppComponent],
  exports: [ApolloModule, HttpLinkModule],
  entryComponents: [ShowImageDialogComponent]
})
export class AppModule {
}
