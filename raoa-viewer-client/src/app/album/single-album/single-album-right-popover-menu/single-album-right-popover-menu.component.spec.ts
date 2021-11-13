import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import { IonicModule } from '@ionic/angular';

import { SingleAlbumRightPopoverMenuComponent } from './single-album-right-popover-menu.component';

describe('SingleAlbumRightPopoverMenuComponent', () => {
  let component: SingleAlbumRightPopoverMenuComponent;
  let fixture: ComponentFixture<SingleAlbumRightPopoverMenuComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ SingleAlbumRightPopoverMenuComponent ],
      imports: [IonicModule.forRoot()]
    }).compileComponents();

    fixture = TestBed.createComponent(SingleAlbumRightPopoverMenuComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }));

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
