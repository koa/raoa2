import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { IonicModule } from '@ionic/angular';

import { StartlistPageRoutingModule } from './startlist-routing.module';

import { StartlistPage } from './startlist.page';

@NgModule({
  imports: [
    CommonModule,
    FormsModule,
    IonicModule,
    StartlistPageRoutingModule
  ],
  declarations: [StartlistPage]
})
export class StartlistPageModule {}
