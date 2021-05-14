import { NgModule } from '@angular/core';
import { Routes, RouterModule } from '@angular/router';

import { SyncPage } from './sync.page';

const routes: Routes = [
  {
    path: '',
    component: SyncPage
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class SyncPageRoutingModule {}
