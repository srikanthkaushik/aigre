import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';

@Component({
  selector: 'app-landing',
  imports: [RouterLink, MatCardModule, MatIconModule, MatButtonModule],
  templateUrl: './landing.html',
  styleUrl: './landing.scss'
})
export class Landing {}
