import { NgModule } from '@angular/core';
import { Routes, RouterModule } from '@angular/router';

import { CompetitionPage } from './competition.page';

const routes: Routes = [
  {
    path: '',
    component: CompetitionPage
  },
  {
    path: 'startlist/:listId',
    loadChildren: () => import('./startlist/startlist.module').then( m => m.StartlistPageModule)
  },
  {
    path: 'day/:day',
    loadChildren: () => import('./day/day.module').then( m => m.DayPageModule)
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class CompetitionPageRoutingModule {}
