import { NgModule } from '@angular/core';
import { Routes, RouterModule } from '@angular/router';

import { ManageTeamsPage } from './manage-teams.page';

const routes: Routes = [
  {
    path: '',
    component: ManageTeamsPage
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class ManageTeamsPageRoutingModule {}
