import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { IonicModule } from '@ionic/angular';

import { ProcessPendingRequestsPageRoutingModule } from './process-pending-requests-routing.module';

import { ProcessPendingRequestsPage } from './process-pending-requests.page';

@NgModule({
  imports: [
    CommonModule,
    FormsModule,
    IonicModule,
    ProcessPendingRequestsPageRoutingModule
  ],
  declarations: [ProcessPendingRequestsPage]
})
export class ProcessPendingRequestsPageModule {}
