import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {PresentationPageRoutingModule} from './presentation-routing.module';

import {PresentationPage} from './presentation.page';
import {AngularResizeEventModule} from 'angular-resize-event';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        PresentationPageRoutingModule,
        AngularResizeEventModule
    ],
    declarations: [PresentationPage]
})
export class PresentationPageModule {
}
