import {NgModule} from '@angular/core';
import {GroupListEditorComponent} from './group-list-editor.component';
import {IonicModule} from '@ionic/angular';
import {CommonModule} from '@angular/common';

@NgModule({
    declarations: [GroupListEditorComponent],
    exports: [
        GroupListEditorComponent
    ],
    imports: [
        IonicModule,
        CommonModule
    ]
})
export class GroupListEditorModule {
}
