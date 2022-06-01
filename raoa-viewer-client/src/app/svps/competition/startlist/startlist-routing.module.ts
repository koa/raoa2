import { NgModule } from '@angular/core';
import { Routes, RouterModule } from '@angular/router';

import { StartlistPage } from './startlist.page';

const routes: Routes = [
  {
    path: '',
    component: StartlistPage
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class StartlistPageRoutingModule {}
