import {BrowserModule} from '@angular/platform-browser';
import {NgModule} from '@angular/core';

import {AppComponent} from './app.component';
import {GraphQLModule} from './graphql.module';
import {HttpClientModule} from '@angular/common/http';
import {AlbumListComponent} from './components/album-list/album-list.component';
import {RouterModule, Routes} from '@angular/router';
import {AlbumContentComponent, ShowImageDialogComponent} from './components/album-content/album-content.component';
import {AngularResizedEventModule} from 'angular-resize-event';
import {ScrollDispatchModule} from '@angular/cdk/scrolling';
import {ScrollingModule as ExperimentalScrollingModule} from '@angular/cdk-experimental/scrolling';
import {
  MatButtonModule,
  MatCardModule,
  MatDialogModule,
  MatFormFieldModule,
  MatIconModule,
  MatInputModule,
  MatListModule,
  MatSidenavModule,
  MatToolbarModule
} from '@angular/material';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {ServiceWorkerModule} from '@angular/service-worker';
import {environment} from '../environments/environment';
import {RequestAccessComponent} from './components/request-access/request-access.component';
import {FormsModule} from '@angular/forms';
import {AcceptRegistrationsComponent} from './components/accept-registrations/accept-registrations.component';
import {HeadlineDirective} from './directive/headline.directive';
import {TitleOnlyHeaderComponent} from './components/title-only-header/title-only-header.component';
import {RequestAccessHeaderComponent} from './components/request-access-header/request-access-header.component';
// import { MatPasswordStrengthModule } from '@angular-material-extensions/core';

const appRouter: Routes = [
  {path: '', component: AlbumListComponent},
  {path: 'album/:id', component: AlbumContentComponent},
  {path: 'requestAccess', component: RequestAccessComponent}
];

@NgModule({
  declarations: [
    AppComponent,
    AlbumListComponent,
    AlbumContentComponent,
    ShowImageDialogComponent,
    RequestAccessComponent,
    AcceptRegistrationsComponent,
    HeadlineDirective,
    TitleOnlyHeaderComponent,
    RequestAccessHeaderComponent
  ],
  imports: [
    BrowserModule,
    GraphQLModule,
    HttpClientModule,
    RouterModule.forRoot(appRouter, {enableTracing: false}),
    AngularResizedEventModule,
    ScrollDispatchModule,
    ExperimentalScrollingModule,
    MatToolbarModule,
    MatSidenavModule,
    BrowserAnimationsModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    ServiceWorkerModule.register('ngsw-worker.js', {enabled: environment.production}),
    MatFormFieldModule,
    MatCardModule,
    MatInputModule,
    FormsModule,
    MatListModule,
    // MatPasswordStrengthModule
  ],
  providers: [],
  bootstrap: [AppComponent],
  entryComponents: [ShowImageDialogComponent, TitleOnlyHeaderComponent, RequestAccessHeaderComponent]
})
export class AppModule {

}
