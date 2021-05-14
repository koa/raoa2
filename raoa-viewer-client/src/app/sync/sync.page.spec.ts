import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import { IonicModule } from '@ionic/angular';

import { SyncPage } from './sync.page';

describe('SyncPage', () => {
  let component: SyncPage;
  let fixture: ComponentFixture<SyncPage>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [ SyncPage ],
      imports: [IonicModule.forRoot()]
    }).compileComponents();

    fixture = TestBed.createComponent(SyncPage);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }));

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
