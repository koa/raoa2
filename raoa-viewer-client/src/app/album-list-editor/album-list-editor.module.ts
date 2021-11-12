import {NgModule} from '@angular/core';
import {AlbumListEditorComponent} from './album-list-editor.component';
import {IonicModule} from '@ionic/angular';
import {CommonModule} from '@angular/common';

@NgModule({
    declarations: [AlbumListEditorComponent],
    exports: [
        AlbumListEditorComponent
    ],
    imports: [
        IonicModule,
        CommonModule
    ]
})
export class AlbumListEditorModule {
}
