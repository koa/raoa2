import {NgModule} from '@angular/core';
import {RouterModule, Routes} from '@angular/router';
import {AlbumContentComponent} from './components/album-content/album-content.component';
import {WelcomeComponent} from './components/welcome/welcome.component';
import {ManageUsersComponent} from './components/manage-users/manage-users.component';


const routes: Routes = [
  {path: 'album/:id', component: AlbumContentComponent},
  {path: '', component: WelcomeComponent},
  {path: 'manageUsers', component: ManageUsersComponent}
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { relativeLinkResolution: 'legacy' })],
  exports: [RouterModule]
})
export class AppRoutingModule {
}
