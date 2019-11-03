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
import {WelcomeComponent} from './components/welcome/welcome.component';
import {MatCardModule} from '@angular/material/card';
import {RequestAccessDialogComponent} from './components/request-access-dialog/request-access-dialog.component';
import {MatFormFieldModule} from '@angular/material/form-field';
import {FormsModule} from '@angular/forms';
import {MatInputModule} from '@angular/material/input';
import {ManageUsersComponent} from './components/manage-users/manage-users.component';
import {MatTabsModule} from '@angular/material/tabs';
import {MatTableModule} from '@angular/material/table';
import {MatButtonToggleModule, MatProgressSpinnerModule, MatSelectModule, MatSlideToggleModule, MatTooltipModule} from '@angular/material';


const appInitializerFn = (appConfig: AppConfigService) => {
  return () => appConfig.loadAppConfig();
};

@NgModule({
  declarations: [
    AppComponent,
    AlbumListComponent,
    AlbumContentComponent,
    ShowImageDialogComponent,
    WelcomeComponent,
    RequestAccessDialogComponent,
    ManageUsersComponent,
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
  entryComponents: [ShowImageDialogComponent, RequestAccessDialogComponent]
})
export class AppModule {
}
