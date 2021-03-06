import {BrowserModule} from '@angular/platform-browser';
import {APP_INITIALIZER, LOCALE_ID, NgModule} from '@angular/core';

import {AppRoutingModule} from './app-routing.module';
import {AppComponent} from './app.component';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {AppConfigService} from './services/app-config.service';
import {HttpClientModule} from '@angular/common/http';
import {MatButtonModule} from '@angular/material/button';
import {ApolloModule} from 'apollo-angular';
import {HttpLinkModule} from 'apollo-angular-link-http';
import {MatIconModule} from '@angular/material/icon';
import {MatSidenavModule} from '@angular/material/sidenav';
import {MatListModule} from '@angular/material/list';
import {MatToolbarModule} from '@angular/material/toolbar';
import {AlbumContentComponent, ShowImageDialogComponent} from './components/album-content/album-content.component';
import {ScrollingModule as ExperimentalScrollingModule} from '@angular/cdk-experimental/scrolling';
import {MatDialogModule} from '@angular/material/dialog';
import {AngularResizedEventModule} from 'angular-resize-event';
import {MatProgressBarModule} from '@angular/material/progress-bar';
import {CookieService} from 'ngx-cookie-service';
import {WelcomeComponent} from './components/welcome/welcome.component';
import {MatCardModule} from '@angular/material/card';
import {RequestAccessDialogComponent} from './components/request-access-dialog/request-access-dialog.component';
import {MatFormFieldModule} from '@angular/material/form-field';
import {FormsModule} from '@angular/forms';
import {MatInputModule} from '@angular/material/input';
import {ManageUsersComponent} from './components/manage-users/manage-users.component';
import {MatTabsModule} from '@angular/material/tabs';
import {MatTableModule} from '@angular/material/table';


import {registerLocaleData} from '@angular/common';
import localeDe from '@angular/common/locales/de';
import {ExposureTimePipe} from './pipe/exposure-time.pipe';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatSelectModule} from '@angular/material/select';
import {MatSlideToggleModule} from '@angular/material/slide-toggle';
import {MatButtonToggleModule} from '@angular/material/button-toggle';
import {MatTooltipModule} from '@angular/material/tooltip';
import {MatMenuModule} from '@angular/material/menu';
import {MatChipsModule} from '@angular/material/chips';
import {MatExpansionModule} from '@angular/material/expansion';
import {ScrollingModule} from '@angular/cdk/scrolling';
import {DBConfig, NgxIndexedDBModule} from 'ngx-indexed-db';
import { ServiceWorkerModule } from '@angular/service-worker';
import { environment } from '../environments/environment';

registerLocaleData(localeDe, 'de');

const appInitializerFn = (appConfig: AppConfigService) => {
  return () => appConfig.loadAppConfig();
};
const dbConfig: DBConfig = {
  name: 'raoa',
  version: 1,
  objectStoresMeta: [{
    store: 'album',
    storeConfig: {keyPath: 'id', autoIncrement: false},
    storeSchema: [
      {name: 'id', keypath: 'id', options: {unique: true}},
      {name: 'version', keypath: 'version', options: {unique: true}},
      {name: 'name', keypath: 'name', options: {unique: false}},
      {name: 'entryCount', keypath: 'entryCount', options: {unique: false}},
      {name: 'albumTime', keypath: 'albumTime', options: {unique: false}}
    ]
  }]
};

@NgModule({
  declarations: [
    AppComponent,
    AlbumContentComponent,
    ShowImageDialogComponent,
    WelcomeComponent,
    RequestAccessDialogComponent,
    ManageUsersComponent,
    ExposureTimePipe,
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
    ExperimentalScrollingModule,
    MatDialogModule,
    AngularResizedEventModule,
    MatProgressBarModule,
    MatCardModule,
    MatFormFieldModule,
    FormsModule,
    MatInputModule,
    MatTabsModule,
    MatTableModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatButtonToggleModule,
    MatTooltipModule,
    MatMenuModule,
    MatChipsModule,
    MatExpansionModule,
    ScrollingModule,
    NgxIndexedDBModule.forRoot(dbConfig),
    ServiceWorkerModule.register('ngsw-worker.js', { enabled: environment.production }),
  ],
  providers: [
    {provide: LOCALE_ID, useValue: 'de-CH'},
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
  entryComponents: [ShowImageDialogComponent, RequestAccessDialogComponent]
})
export class AppModule {
}
