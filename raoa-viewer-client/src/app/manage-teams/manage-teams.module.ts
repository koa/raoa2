import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { IonicModule } from '@ionic/angular';

import { ManageTeamsPageRoutingModule } from './manage-teams-routing.module';

import { ManageTeamsPage } from './manage-teams.page';

@NgModule({
  imports: [
    CommonModule,
    FormsModule,
    IonicModule,
    ManageTeamsPageRoutingModule
  ],
  declarations: [ManageTeamsPage]
})
export class ManageTeamsPageModule {}
