import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { IonicModule } from '@ionic/angular';

import { ManageAlbumEditTeamsPageRoutingModule } from './manage-album-edit-teams-routing.module';

import { ManageAlbumEditTeamsPage } from './manage-album-edit-teams.page';

@NgModule({
  imports: [
    CommonModule,
    FormsModule,
    IonicModule,
    ManageAlbumEditTeamsPageRoutingModule
  ],
  declarations: [ManageAlbumEditTeamsPage]
})
export class ManageAlbumEditTeamsPageModule {}
