import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { IonicModule } from '@ionic/angular';

import { AlbumPageRoutingModule } from './album-routing.module';

import { AlbumPage } from './album.page';

@NgModule({
  imports: [
    CommonModule,
    FormsModule,
    IonicModule,
    AlbumPageRoutingModule
  ],
  declarations: [AlbumPage]
})
export class AlbumPageModule {}
