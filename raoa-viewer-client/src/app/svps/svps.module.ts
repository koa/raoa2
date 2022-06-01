import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { IonicModule } from '@ionic/angular';

import { SvpsPageRoutingModule } from './svps-routing.module';

import { SvpsPage } from './svps.page';

@NgModule({
  imports: [
    CommonModule,
    FormsModule,
    IonicModule,
    SvpsPageRoutingModule
  ],
  declarations: [SvpsPage]
})
export class SvpsPageModule {}
