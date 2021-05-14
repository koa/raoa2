import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { IonicModule } from '@ionic/angular';

import { SyncPageRoutingModule } from './sync-routing.module';

import { SyncPage } from './sync.page';

@NgModule({
  imports: [
    CommonModule,
    FormsModule,
    IonicModule,
    SyncPageRoutingModule
  ],
  declarations: [SyncPage]
})
export class SyncPageModule {}
