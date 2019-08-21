import {async, ComponentFixture, TestBed} from '@angular/core/testing';

import {RequestAccessHeaderComponent} from './request-access-header.component';

describe('RequestAccessHeaderComponent', () => {
  let component: RequestAccessHeaderComponent;
  let fixture: ComponentFixture<RequestAccessHeaderComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [RequestAccessHeaderComponent]
    })
      .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(RequestAccessHeaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
