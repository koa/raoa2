import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import { IonicModule } from '@ionic/angular';

import { ManageAlbumEditTeamsPage } from './manage-album-edit-teams.page';

describe('ManageAlbumEditTeamsPage', () => {
  let component: ManageAlbumEditTeamsPage;
  let fixture: ComponentFixture<ManageAlbumEditTeamsPage>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ ManageAlbumEditTeamsPage ],
      imports: [IonicModule.forRoot()]
    }).compileComponents();

    fixture = TestBed.createComponent(ManageAlbumEditTeamsPage);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }));

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
