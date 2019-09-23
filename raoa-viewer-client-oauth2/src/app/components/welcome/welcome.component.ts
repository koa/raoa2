import {Component, OnInit} from '@angular/core';
import {AppConfigService} from '../../services/app-config.service';

@Component({
  selector: 'app-welcome',
  templateUrl: './welcome.component.html',
  styleUrls: ['./welcome.component.css']
})
export class WelcomeComponent implements OnInit {
  public signedIn: boolean;

  constructor(public appConfigService: AppConfigService) {
  }

  ngOnInit() {
    this.appConfigService.renderButton('sign-in-button');
    this.appConfigService.signedInObservable.subscribe(signedIn => {
      this.signedIn = signedIn;
      console.log('signed in ' + signedIn);
    });
  }

}
