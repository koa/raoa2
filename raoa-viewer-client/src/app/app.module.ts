import {BrowserModule} from '@angular/platform-browser';
import {NgModule} from '@angular/core';

import {AppComponent} from './app.component';
import {GraphQLModule} from './graphql.module';
import {HttpClientModule} from '@angular/common/http';
import {AlbumListComponent} from './components/album-list/album-list.component';
import {RouterModule, Routes} from '@angular/router';
import {AlbumContentComponent} from './components/album-content/album-content.component';
import {AngularResizedEventModule} from 'angular-resize-event';
import {ScrollDispatchModule} from '@angular/cdk/scrolling';
import {ScrollingModule as ExperimentalScrollingModule} from '@angular/cdk-experimental/scrolling';
import {MatSidenavModule, MatToolbarModule} from '@angular/material';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {LightboxModule} from 'ngx-lightbox';
// import { MatPasswordStrengthModule } from '@angular-material-extensions/core';

const appRouter: Routes = [
  {path: '', component: AlbumListComponent},
  {path: 'album/:id', component: AlbumContentComponent}
];

@NgModule({
  declarations: [
    AppComponent,
    AlbumListComponent,
    AlbumContentComponent
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
    LightboxModule,
    // MatPasswordStrengthModule
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule {
}
