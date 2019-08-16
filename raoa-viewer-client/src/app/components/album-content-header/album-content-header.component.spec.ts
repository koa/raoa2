import {async, ComponentFixture, TestBed} from '@angular/core/testing';

import {AlbumContentHeaderComponent} from './album-content-header.component';

describe('AlbumContentHeaderComponent', () => {
  let component: AlbumContentHeaderComponent;
  let fixture: ComponentFixture<AlbumContentHeaderComponent>;

  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [AlbumContentHeaderComponent]
    })
      .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(AlbumContentHeaderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
