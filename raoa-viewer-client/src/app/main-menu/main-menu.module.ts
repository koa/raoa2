import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { IonicModule } from '@ionic/angular';

import { MainMenuComponent } from './main-menu.component';
import {RouterModule} from '@angular/router';

@NgModule({
    imports: [CommonModule, FormsModule, IonicModule, RouterModule,],
  declarations: [MainMenuComponent],
  exports: [MainMenuComponent]
})
export class MainMenuComponentModule {}
