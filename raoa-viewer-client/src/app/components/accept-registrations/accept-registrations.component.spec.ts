import {async, ComponentFixture, TestBed} from '@angular/core/testing';

import {AcceptRegistrationsComponent} from './accept-registrations.component';

describe('AcceptRegistrationsComponent', () => {
  let component: AcceptRegistrationsComponent;
  let fixture: ComponentFixture<AcceptRegistrationsComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [AcceptRegistrationsComponent]
    })
      .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(AcceptRegistrationsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
