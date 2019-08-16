import {async, ComponentFixture, TestBed} from '@angular/core/testing';

import {TitleOnlyHeaderComponent} from './title-only-header.component';

describe('TitleOnlyHeaderComponent', () => {
  let component: TitleOnlyHeaderComponent;
  let fixture: ComponentFixture<TitleOnlyHeaderComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [TitleOnlyHeaderComponent]
    })
      .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(TitleOnlyHeaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
