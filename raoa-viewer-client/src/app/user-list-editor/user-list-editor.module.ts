import {NgModule} from '@angular/core';
import {UserListEditorComponent} from './user-list-editor.component';
import {IonicModule} from '@ionic/angular';
import {CommonModule} from '@angular/common';

@NgModule({
    exports: [
        UserListEditorComponent
    ],
    imports: [
        IonicModule,
        CommonModule
    ],
    declarations: [UserListEditorComponent]
})
export class UserListEditorModule {
}
