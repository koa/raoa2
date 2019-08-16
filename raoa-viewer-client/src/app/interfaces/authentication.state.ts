import {UserData} from './user.data';

export enum AuthenticationStateEnum {
  UNKNOWN,
  AUTHENTICATED,
  AUTHORIZATION_REQUESTED,
  AUTHORIZED
}

export interface AuthenticationState {
  state: AuthenticationStateEnum;
  user: UserData;
}
