import {NgModule} from '@angular/core';
import {RouterModule, Routes} from '@angular/router';
import {LoginComponent} from './components/login/login.component';
import {AlbumListComponent} from './components/album-list/album-list.component';
import {AlbumContentComponent} from './components/album-content/album-content.component';


const routes: Routes = [
  {path: 'login', component: LoginComponent},
  {path: 'album/:id', component: AlbumContentComponent},
  {path: '', component: AlbumListComponent}
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule {
}
