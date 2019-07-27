import {BrowserModule} from '@angular/platform-browser';
import {NgModule} from '@angular/core';

import {AppComponent} from './app.component';
import {GraphQLModule} from './graphql.module';
import {HttpClientModule} from '@angular/common/http';
import {AlbumListComponent} from './components/album-list/album-list.component';
import {RouterModule, Routes} from '@angular/router';
import {AlbumContentComponent} from './components/album-content/album-content.component';

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
    // MatPasswordStrengthModule
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule {
}
