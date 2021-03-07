import { NgModule } from '@angular/core';
import { Routes, RouterModule } from '@angular/router';

import { ProcessPendingRequestsPage } from './process-pending-requests.page';

const routes: Routes = [
  {
    path: '',
    component: ProcessPendingRequestsPage
  }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class ProcessPendingRequestsPageRoutingModule {}
