import {NgModule} from '@angular/core';
import {RouterModule, Routes} from '@angular/router';
import {AlbumListComponent} from './components/album-list/album-list.component';
import {AlbumContentComponent} from './components/album-content/album-content.component';
import {WelcomeComponent} from './components/welcome/welcome.component';


const routes: Routes = [
  {path: 'album/:id', component: AlbumContentComponent},
  {path: '', component: WelcomeComponent}
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule {
}
