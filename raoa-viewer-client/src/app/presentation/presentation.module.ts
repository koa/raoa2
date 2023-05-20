import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {PresentationPageRoutingModule} from './presentation-routing.module';

import {PresentationPage} from './presentation.page';
import {NgxResize} from 'ngx-resize';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        PresentationPageRoutingModule,
        NgxResize,
    ],
    declarations: [PresentationPage]
})
export class PresentationPageModule {
}
