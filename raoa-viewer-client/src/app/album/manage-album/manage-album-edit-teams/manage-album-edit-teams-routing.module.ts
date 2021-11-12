import { NgModule } from '@angular/core';
import { Routes, RouterModule } from '@angular/router';

import { ManageAlbumEditTeamsPage } from './manage-album-edit-teams.page';

const routes: Routes = [
  {
    path: '',
    component: ManageAlbumEditTeamsPage
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class ManageAlbumEditTeamsPageRoutingModule {}
